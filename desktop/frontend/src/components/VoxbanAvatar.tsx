import { useEffect, useState } from "react";

interface Props {
  size?: number;
  mood?: "idle" | "listening" | "thinking";
  onClick?: () => void;
}

/** Interactive blinking lime-emerald Voxban avatar aligned with the Android APK character. */
export function VoxbanAvatar({ size = 44, mood = "idle", onClick }: Props) {
  const [blink, setBlink] = useState(false);
  const [giggle, setGiggle] = useState(false);

  useEffect(() => {
    const id = setInterval(() => {
      setBlink(true);
      setTimeout(() => setBlink(false), 140);
    }, 3800);
    return () => clearInterval(id);
  }, []);

  return (
    <button
      type="button"
      onClick={() => {
        setGiggle(true);
        setTimeout(() => setGiggle(false), 500);
        onClick?.();
      }}
      style={{ width: size, height: size }}
      className="relative rounded-full flex items-center justify-center shrink-0 cursor-pointer border-0 bg-transparent p-0 focus:outline-none"
      title="Voxban AI"
    >
      {mood === "listening" && (
        <span className="absolute inset-0 rounded-full animate-ping bg-[#EAFF87]/60" />
      )}
      <svg
        viewBox="0 0 80 80"
        width={size}
        height={size}
        className={`transition-transform duration-200 ${giggle ? "scale-110 rotate-6" : ""}`}
      >
        <defs>
          <radialGradient id="vkAvatarGrad" cx="35%" cy="28%" r="72%">
            <stop offset="0%" stopColor="#EAFF87" />
            <stop offset="55%" stopColor="#8CE08A" />
            <stop offset="100%" stopColor="#2F7E57" />
          </radialGradient>
        </defs>
        <circle cx="40" cy="40" r="38" fill="url(#vkAvatarGrad)" />
        {/* Eyes */}
        {blink ? (
          <>
            <path d="M25 36 Q29 39 33 36" stroke="#1B201C" strokeWidth="3" strokeLinecap="round" fill="none" />
            <path d="M47 36 Q51 39 55 36" stroke="#1B201C" strokeWidth="3" strokeLinecap="round" fill="none" />
          </>
        ) : (
          <>
            <circle cx="29" cy="35" r="3.6" fill="#1B201C" />
            <circle cx="51" cy="35" r="3.6" fill="#1B201C" />
            <circle cx="30.2" cy="33.8" r="1.2" fill="#FFFFFF" />
            <circle cx="52.2" cy="33.8" r="1.2" fill="#FFFFFF" />
          </>
        )}
        {/* Mouth */}
        {mood === "thinking" ? (
          <circle cx="40" cy="48" r="3.5" fill="#1B201C" />
        ) : mood === "listening" || giggle ? (
          <path d="M31 45 Q40 55 49 45" stroke="#1B201C" strokeWidth="3.2" strokeLinecap="round" fill="none" />
        ) : (
          <path d="M33 46 Q40 52 47 46" stroke="#1B201C" strokeWidth="3" strokeLinecap="round" fill="none" />
        )}
      </svg>
    </button>
  );
}
